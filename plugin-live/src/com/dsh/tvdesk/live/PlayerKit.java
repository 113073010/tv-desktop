package com.dsh.tvdesk.live;

import android.content.Context;

import java.io.File;

import tv.danmaku.ijk.media.player.IjkLibLoader;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * 插件自带 IJK（ffmpeg）内核：系统播放器吃不下电信 IPTV 的 rtsp/smil。
 *
 * 关键坑：插件运行在桌面进程里，context.getApplicationInfo().nativeLibraryDir
 * 返回的是「桌面自己的」lib 目录（/system/app/T2Launcher/lib/arm），
 * 而插件自己的 so 被桌面框架解到了 files/plugins_data/<包名>/libs。
 * 必须自己找对目录，否则 dlopen 失败、prepare 直接 -10000。
 */
public class PlayerKit {

    private static final String SELF_PKG = "com.dsh.tvdesk.live";
    private static boolean sReady;
    private static String sDir;

    public static synchronized void init(Context ctx) {
        if (sReady) return;
        sReady = true;

        String hostPkg = ctx.getPackageName();
        String[] candidates = new String[]{
                "/data/data/" + hostPkg + "/files/plugins_data/" + SELF_PKG + "/libs",
                "/data/data/" + hostPkg + "/files/plugins_data/" + SELF_PKG + "/lib",
                ctx.getApplicationInfo().nativeLibraryDir,
                "/data/app-lib/" + hostPkg
        };

        for (String c : candidates) {
            if (c == null) continue;
            File probe = new File(c, "libijkffmpeg.so");
            if (probe.exists()) {
                sDir = c;
                break;
            }
        }
        Diag.log("ijk", "lib dir = " + sDir);

        if (sDir != null) {
            try {
                IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                    @Override public void loadLibrary(String name) {
                        System.load(sDir + "/lib" + name + ".so");
                    }
                });
                Diag.log("ijk", "loaded ok from " + sDir);
                return;
            } catch (Throwable t) {
                Diag.err("ijk", "load with dir failed " + t);
            }
        }

        try {
            IjkMediaPlayer.loadLibrariesOnce(null);
            Diag.log("ijk", "loaded via loadLibrary");
        } catch (Throwable t) {
            Diag.err("ijk", "load failed " + t);
        }
    }

    public static IjkMediaPlayer create() {
        return new IjkMediaPlayer();
    }
}
