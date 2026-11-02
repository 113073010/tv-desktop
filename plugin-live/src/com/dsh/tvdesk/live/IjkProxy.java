package com.dsh.tvdesk.live;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.Surface;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import dalvik.system.DexClassLoader;

/**
 * 借用电视上已安装的 TVBox（com.github.tvbox.osc.tk）自带的 IJK 内核。
 *
 * 原因：Maven 上的 ijkplayer-armv7a 是阉割版，libijkffmpeg.so 里根本没有 rtsp
 * 协议（实测字符串 count = 0），播电信 IPTV 的 rtsp/smil 直接 "Protocol not found"。
 * TVBox 那份是带 rtsp 的，而且它的 Java 层和 so 是同一版本编译的，配套使用不会崩。
 *
 * 做法：把 TVBox 的 base.apk 当类路径，libraryPath 指向它自己的 nativeLibraryDir，
 * 然后全部通过反射调用（接口类型来自它的 ClassLoader，所以监听器用 Proxy 实现）。
 */
public class IjkProxy {

    private static final String HOST_APP = "com.github.tvbox.osc.tk";
    private static final String CLS_PLAYER = "tv.danmaku.ijk.media.player.IjkMediaPlayer";
    private static final String CLS_LIB_LOADER = "tv.danmaku.ijk.media.player.IjkLibLoader";
    private static final String CLS_ON_PREPARED = "tv.danmaku.ijk.media.player.IMediaPlayer$OnPreparedListener";
    private static final String CLS_ON_ERROR = "tv.danmaku.ijk.media.player.IMediaPlayer$OnErrorListener";

    private static boolean sTried;
    private static boolean sReady;
    private static ClassLoader sLoader;
    private static Class<?> sPlayerCls;
    private static Class<?> sOnPreparedCls;
    private static Class<?> sOnErrorCls;

    public interface Callback {
        void onReady(int width, int height);
        void onError(int what, int extra);
    }

    public static synchronized boolean init(Context ctx) {
        if (sTried) return sReady;
        sTried = true;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(HOST_APP, 0);
            String apk = ai.sourceDir;
            String libDir = ai.nativeLibraryDir;
            File dexOut = new File(ctx.getCacheDir(), "ijkdex");
            if (!dexOut.exists()) dexOut.mkdirs();

            Diag.log("proxy", "apk=" + apk);
            Diag.log("proxy", "lib=" + libDir);

            sLoader = new DexClassLoader(apk, dexOut.getAbsolutePath(), libDir, ctx.getClassLoader());
            sPlayerCls = sLoader.loadClass(CLS_PLAYER);
            sOnPreparedCls = sLoader.loadClass(CLS_ON_PREPARED);
            sOnErrorCls = sLoader.loadClass(CLS_ON_ERROR);

            Class<?> libIface = sLoader.loadClass(CLS_LIB_LOADER);
            Object libLoader = Proxy.newProxyInstance(sLoader, new Class[]{libIface},
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if (args != null && args.length > 0 && args[0] instanceof String) {
                                System.loadLibrary((String) args[0]);
                            }
                            return null;
                        }
                    });
            Method loadOnce = sPlayerCls.getMethod("loadLibrariesOnce", libIface);
            // 传 null：让 IjkMediaPlayer 自己的类（在 TVBox 的 ClassLoader 里）去调
            // System.loadLibrary，JNI 注册才会落在正确的加载器上；用我们这边的 loader
            // 去 load，会导致 native_init 找不到实现。
            try {
                loadOnce.invoke(null, new Object[]{null});
                Diag.log("proxy", "loaded via default lib loader");
            } catch (Throwable t) {
                Diag.log("proxy", "default loader failed, retry custom: " + t.getCause());
                loadOnce.invoke(null, libLoader);
            }
            sReady = true;
            Diag.log("proxy", "ijk ready");
        } catch (Throwable t) {
            Diag.err("proxy", "init failed " + t);
            sReady = false;
        }
        return sReady;
    }

    public static boolean isReady() {
        return sReady;
    }

    /** 一个可播放的实例，所有调用都走反射。 */
    public static class Player {
        private Object p;
        private boolean released;

        public Player() throws Exception {
            p = sPlayerCls.getConstructor().newInstance();
        }

        public void setSurface(Surface surface) throws Exception {
            sPlayerCls.getMethod("setSurface", Surface.class).invoke(p, surface);
        }

        public void setDataSource(String url) throws Exception {
            sPlayerCls.getMethod("setDataSource", String.class).invoke(p, url);
        }

        /** 让 MStar 这颗弱芯片尽量省力：硬解优先、丢帧、低缓冲、RTSP 走 TCP */
        public void applyTvOptions() {
            // ---- 解码 ----
            setOption(4, "mediacodec", 1);
            setOption(4, "mediacodec-all-videos", 1);
            setOption(4, "mediacodec-handle-resolution-change", 1);
            setOption(4, "mediacodec-sync", 0);

            // ---- 渲染（4K 清晰度关键）----
            // rv32 走 RGBA 直通，避免默认 YUV 转换带来的糊/色偏
            setOption(4, "overlay-format", "fcc-rv32");
            setOption(4, "enable-accuracy", 1);
            setOption(4, "frame-queue-size", 3);

            // ---- 网络与缓冲 ----
            setOption(4, "framedrop", 1);
            setOption(4, "packet-buffering", 0);
            setOption(4, "max-buffer-size", 1024 * 1024);
            setOption(4, "reconnect", 1);
            setOption(4, "timeout", 15000);

            // ---- ffmpeg 侧：4K HEVC 探测窗口要够大 ----
            setOption(1, "rtsp_transport", "tcp");
            setOption(1, "stimeout", "15000000");
            setOption(1, "probesize", "2000000");
            setOption(1, "analyzeduration", "2000000");
            setOption(1, "fflags", "nobuffer");
            setOption(1, "max_delay", "500000");
        }

        private void setOption(int cat, String name, long value) {
            try {
                sPlayerCls.getMethod("setOption", int.class, String.class, long.class)
                        .invoke(p, cat, name, value);
            } catch (Throwable ignored) { }
        }

        private void setOption(int cat, String name, String value) {
            try {
                sPlayerCls.getMethod("setOption", int.class, String.class, String.class)
                        .invoke(p, cat, name, value);
            } catch (Throwable ignored) { }
        }

        public void prepareAsync() throws Exception {
            sPlayerCls.getMethod("prepareAsync").invoke(p);
        }

        public void start() throws Exception {
            sPlayerCls.getMethod("start").invoke(p);
        }

        public int getVideoWidth() throws Exception {
            return (Integer) sPlayerCls.getMethod("getVideoWidth").invoke(p);
        }

        public int getVideoHeight() throws Exception {
            return (Integer) sPlayerCls.getMethod("getVideoHeight").invoke(p);
        }

        public void stop() {
            try { sPlayerCls.getMethod("stop").invoke(p); } catch (Throwable ignored) { }
        }

        public void release() {
            if (released) return;
            released = true;
            try { sPlayerCls.getMethod("release").invoke(p); } catch (Throwable ignored) { }
        }

        public void setCallback(final Callback cb) throws Exception {
            Object onPrepared = Proxy.newProxyInstance(sLoader, new Class[]{sOnPreparedCls},
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onPrepared".equals(method.getName())) {
                                try {
                                    int w = getVideoWidth();
                                    int h = getVideoHeight();
                                    cb.onReady(w, h);
                                } catch (Throwable t) {
                                    Diag.err("proxy", "prepared cb " + t);
                                }
                                return null;
                            }
                            return null;
                        }
                    });
            sPlayerCls.getMethod("setOnPreparedListener", sOnPreparedCls).invoke(p, onPrepared);

            Object onError = Proxy.newProxyInstance(sLoader, new Class[]{sOnErrorCls},
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onError".equals(method.getName()) && args != null && args.length >= 3) {
                                cb.onError((Integer) args[1], (Integer) args[2]);
                                return Boolean.TRUE;
                            }
                            return Boolean.FALSE;
                        }
                    });
            sPlayerCls.getMethod("setOnErrorListener", sOnErrorCls).invoke(p, onError);
        }
    }
}
