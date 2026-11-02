package com.dsh.tvdesk.stock;

import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 轻量日志：写 /sdcard/tvdesk_stock.log，同时进 logcat */
public class Diag {

    private static final String PATH = "/sdcard/tvdesk_stock.log";
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    public static synchronized void log(String tag, String msg) {
        String line = FMT.format(new Date()) + " [" + tag + "] " + msg;
        try {
            FileWriter w = new FileWriter(PATH, true);
            w.write(line);
            w.write("\n");
            w.close();
        } catch (Throwable ignored) { }
        android.util.Log.i("TVDeskStock", tag + " " + msg);
    }
}
