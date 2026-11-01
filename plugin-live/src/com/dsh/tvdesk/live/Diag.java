package com.dsh.tvdesk.live;

import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 同时写 logcat 和 /sdcard/tvdesk.log，避免日志被系统缓冲冲掉。 */
public class Diag {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static boolean sFileOk = true;

    public static void log(String tag, String msg) {
        android.util.Log.i("TVDesk", tag + " " + msg);
        if (!sFileOk) return;
        FileWriter w = null;
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "tvdesk.log");
            if (f.length() > 512 * 1024) {
                // 超过 512K 就重来，避免无限增长
                f.delete();
            }
            w = new FileWriter(f, true);
            w.write(FMT.format(new Date()) + " [" + tag + "] " + msg + "\n");
        } catch (Throwable t) {
            sFileOk = false;
        } finally {
            try { if (w != null) w.close(); } catch (Exception ignored) { }
        }
    }

    public static void err(String tag, String msg) {
        android.util.Log.e("TVDesk", tag + " " + msg);
        log(tag, "ERROR " + msg);
    }
}
