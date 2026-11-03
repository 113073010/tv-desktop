package com.dsh.tvdesk.stock;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 轻量日志：写 /sdcard/tvdesk_stock.log，同时进 logcat；超过 512K 自动清空重来 */
public class Diag {

    private static final String PATH = "/sdcard/tvdesk_stock.log";
    private static final long MAX = 512 * 1024;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    public static synchronized void log(String tag, String msg) {
        String line = FMT.format(new Date()) + " [" + tag + "] " + msg;
        try {
            File f = new File(PATH);
            if (f.length() > MAX) f.delete();
            FileWriter w = new FileWriter(PATH, true);
            w.write(line);
            w.write("\n");
            w.close();
        } catch (Throwable ignored) { }
        android.util.Log.i("TVDeskStock", tag + " " + msg);
    }
}
