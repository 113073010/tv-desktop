package com.dsh.tvdesk.restore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * TAB 恢复工具
 *
 * 四个动作：
 *   安装依赖  —— 安装 TVBox（直播插件的播放内核来源）+ 写入频道源
 *   恢复设置  —— 写入三个插件 APK + 桌面数据库
 *   清除设置  —— 删除插件、清理数据、把三个 TAB 从桌面隐藏
 *   清除依赖  —— 卸载 TVBox、删除频道源
 */
public class MainActivity extends Activity {

    private static final String LAUNCHER = "/data/data/com.stv.launcher";
    private static final String PLUGINS = LAUNCHER + "/files/plugins";
    private static final String CH_DIR = "/sdcard/DIYP影音";

    private static final String[] APKS = {"news", "live", "douban"};
    private static final String[] CH_FILES = {"cctv", "local", "digital", "jilin"};
    private static final String[] CH_NAMES = {"央视", "地方", "数字", "吉林"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView mLog;
    private volatile boolean mRootOk = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF10141D);
        root.setPadding(dp(46), dp(34), dp(46), dp(26));

        TextView title = new TextView(this);
        title.setText("TAB 恢复工具");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("新闻 / 直播 / 豆瓣荐片   ·   需要 root");
        sub.setTextColor(0xFF9AA3B2);
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        root.addView(sub, slp);

        // 2x2 按钮
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(18);
        root.addView(row1, rlp);

        Button b1 = btn("① 安装依赖", 0xFF2674EE, new Runnable() {
            public void run() { installDeps(); }
        });
        row1.addView(b1, btnLp(true));
        Button b2 = btn("② 恢复设置", 0xFF1F8A4C, new Runnable() {
            public void run() { restoreSettings(); }
        });
        row1.addView(b2, btnLp(false));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.topMargin = dp(12);
        root.addView(row2, rlp2);

        Button b3 = btn("③ 清除设置", 0xFF8A3B1F, new Runnable() {
            public void run() { confirm("清除设置", "将彻底移除三个 TAB：\n· 删除插件 APK 和缓存数据\n· 从桌面数据库删除这三个记录\n\n清除后桌面上不再出现它们（也不会留在“待添加”）。\n数据库会先备份到 /sdcard/launcher_new.before_clear.db。\n\n确定继续？", new Runnable() {
                public void run() { clearSettings(); }
            }); }
        });
        row2.addView(b3, btnLp(true));
        Button b4 = btn("④ 清除依赖", 0xFF8A1F3B, new Runnable() {
            public void run() { confirm("清除依赖", "将卸载 TVBox 并删除频道源目录。\n\n这会同时影响其他需要 TVBox 的功能。\n确定继续？", new Runnable() {
                public void run() { clearDeps(); }
            }); }
        });
        row2.addView(b4, btnLp(false));

        TextView tip = new TextView(this);
        tip.setText("说明：① 装好 TVBox 和频道源之后，② 才能让直播 TAB 用起来。");
        tip.setTextColor(0xFF6C7484);
        tip.setTextSize(13);
        tip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        root.addView(tip, tlp);

        mLog = new TextView(this);
        mLog.setTextColor(0xFFB8C0CC);
        mLog.setTextSize(13);
        mLog.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svlp.topMargin = dp(14);
        sv.addView(mLog);
        root.addView(sv, svlp);

        setContentView(root);

        // 方向键导航 + 默认焦点
        b1.setNextFocusRightId(b2.getId());
        b2.setNextFocusLeftId(b1.getId());
        b2.setNextFocusDownId(b4.getId());
        b4.setNextFocusUpId(b2.getId());
        b3.setNextFocusRightId(b4.getId());
        b4.setNextFocusLeftId(b3.getId());
        b3.setNextFocusUpId(b1.getId());
        b1.setNextFocusDownId(b3.getId());
        b1.post(new Runnable() {
            @Override public void run() { b1.requestFocus(); }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                checkRoot();
                ui.post(new Runnable() {
                    @Override public void run() {
                        log(mRootOk ? "root: 已授权 ✓" : "root: 未授权 —— 请在弹出的提示里选「授权」");
                    }
                });
            }
        }).start();
        log("准备就绪。先按 ① 安装依赖，再按 ② 恢复设置。");
    }

    private Button btn(String text, final int color, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setBackground(bg(color, false));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setId(View.generateViewId());
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(bg(color, hasFocus));
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return b;
    }

    private LinearLayout.LayoutParams btnLp(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), dp(66));
        if (left) lp.rightMargin = dp(14);
        return lp;
    }

    private void confirm(String title, String msg, final Runnable yes) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { yes.run(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 1. 安装依赖 =================

    private void installDeps() {

        log("\n=== 安装依赖 ===");
        run(new Runnable() {
            @Override public void run() {
                File tvbox = unpack("tvbox.apk", "tvbox.apk");
                if (tvbox != null) {
                    log("TVBox 已解包，安装中（28MB，约 20 秒）…");
                    exec("pm install -r " + tvbox.getAbsolutePath());
                    log("TVBox 检查: " + (hasPackage("com.github.tvbox.osc.tk") ? "已安装 ✓" : "安装失败 ✗"));
                } else {
                    log("内置 TVBox 解包失败 ✗");
                }

                log("写入频道源…");
                File dir = new File(CH_DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    log("  /sdcard 目录创建失败");
                }
                int ok = 0;
                for (int i = 0; i < CH_FILES.length; i++) {
                    File src = unpack("ch_" + CH_FILES[i] + ".txt", CH_FILES[i] + ".txt");
                    if (src == null) { log("  解包失败: " + CH_NAMES[i]); continue; }
                    File dst = new File(dir, CH_NAMES[i] + ".txt");
                    if (copyFile(src, dst)) { ok++; log("  " + CH_NAMES[i] + ".txt ✓"); }
                    else log("  " + CH_NAMES[i] + ".txt 写入失败");
                }
                log("频道源已写入 " + CH_DIR + "（" + ok + "/4）");
                log("依赖处理完成");
            }
        });
    }

    // ================= 2. 恢复设置 =================

    private void restoreSettings() {
        if (!mRootOk && !checkRoot()) { log("没有 root 权限，无法继续"); return; }
        log("\n=== 恢复设置 ===");
        run(new Runnable() {
            @Override public void run() {
                File dir = getCacheDir();
                for (String n : APKS) {
                    if (unpack("tab_" + n + ".apk", "tab_" + n + ".apk") == null) {
                        log("插件解包失败: " + n);
                        return;
                    }
                }
                File db = unpack("db_new.db", "launcher_new.db");
                if (db == null) { log("数据库解包失败"); return; }

                StringBuilder sh = new StringBuilder();
                sh.append("mkdir -p ").append(PLUGINS).append("\n");
                sh.append("cp -f ").append(LAUNCHER).append("/databases/launcher_new.db ")
                  .append("/sdcard/launcher_new.before_restore.db 2>/dev/null\n");
                for (String n : APKS) {
                    File f = new File(dir, "tab_" + n + ".apk");
                    sh.append("cp -f '").append(f.getAbsolutePath()).append("' ")
                      .append(PLUGINS).append("/").append(n).append(".apk\n");
                    sh.append("chown system:system ").append(PLUGINS).append("/").append(n).append(".apk\n");
                    sh.append("chmod 600 ").append(PLUGINS).append("/").append(n).append(".apk\n");
                    sh.append("rm -rf ").append(LAUNCHER).append("/plugins_data/com.dsh.tvdesk.").append(n).append("\n");
                }
                sh.append("rm -f ").append(LAUNCHER).append("/databases/launcher_new.db-journal\n");
                sh.append("cp -f '").append(db.getAbsolutePath()).append("' ")
                  .append(LAUNCHER).append("/databases/launcher_new.db\n");
                sh.append("chown system:system ").append(LAUNCHER).append("/databases/launcher_new.db\n");
                sh.append("chmod 660 ").append(LAUNCHER).append("/databases/launcher_new.db\n");
                sh.append("sync\n");
                sh.append("am force-stop com.stv.launcher\n");
                sh.append("sleep 2\n");
                sh.append("am start -n com.stv.launcher/.activity.Launcher\n");
                sh.append("exit 0\n");

                ShResult r = execScript(sh.toString(), "restore");
                StringBuilder pathList = new StringBuilder();
                for (String n : APKS) pathList.append(PLUGINS).append("/").append(n).append(".apk ");
                pathList.append(LAUNCHER).append("/databases/launcher_new.db");
                ShResult chk = exec("ls -l " + pathList);
                int okCount = 0;
                for (String n : APKS) {
                    if (chk.out.contains("/" + n + ".apk")) okCount++;
                    else log("  校验失败: " + n + ".apk");
                }
                boolean dbOk = chk.out.contains("launcher_new.db");
                if (okCount == APKS.length && dbOk) {
                    log("设置已恢复（3 个插件 + 数据库），桌面重启中");
                } else {
                    log("恢复不完整：插件 " + okCount + "/3，数据库 " + (dbOk ? "正常" : "缺失")
                            + "（退出码 " + r.code + "）");
                }
            }
        });
    }

    // ================= 3. 清除设置 =================

    private void clearSettings() {
        if (!mRootOk && !checkRoot()) { log("没有 root 权限，无法继续"); return; }
        log("\n=== 清除设置 ===");
        run(new Runnable() {
            @Override public void run() {
                File db = unpack("db_clean.db", "launcher_clean.db");
                StringBuilder sh = new StringBuilder();
                sh.append("cp -f ").append(LAUNCHER).append("/databases/launcher_new.db ")
                  .append("/sdcard/launcher_new.before_clear.db 2>/dev/null\n");
                for (String n : APKS) {
                    sh.append("rm -f ").append(PLUGINS).append("/").append(n).append(".apk\n");
                    sh.append("rm -rf ").append(LAUNCHER).append("/plugins_data/com.dsh.tvdesk.").append(n).append("\n");
                }
                if (db != null) {
                    sh.append("rm -f ").append(LAUNCHER).append("/databases/launcher_new.db-journal\n");
                    sh.append("cp -f '").append(db.getAbsolutePath()).append("' ")
                      .append(LAUNCHER).append("/databases/launcher_new.db\n");
                    sh.append("chown system:system ").append(LAUNCHER).append("/databases/launcher_new.db\n");
                    sh.append("chmod 660 ").append(LAUNCHER).append("/databases/launcher_new.db\n");
                }
                sh.append("sync\n");
                sh.append("am force-stop com.stv.launcher\n");
                sh.append("sleep 2\n");
                sh.append("am start -n com.stv.launcher/.activity.Launcher\n");
                sh.append("exit 0\n");
                ShResult r = execScript(sh.toString(), "clear");
                StringBuilder pl = new StringBuilder();
                for (String n : APKS) pl.append(PLUGINS).append("/").append(n).append(".apk ");
                ShResult chk2 = exec("ls " + pl);
                int left = 0;
                for (String n : APKS) {
                    if (chk2.out.contains("/" + n + ".apk")) left++;
                }
                log(left == 0 ? "已彻底清除：插件已删除，数据库记录已移除"
                              : "清除不完整：仍有 " + left + " 个插件文件（退出码 " + r.code + "）");
            }
        });
    }

    // ================= 4. 清除依赖 =================

    private void clearDeps() {
        if (!mRootOk && !checkRoot()) { log("没有 root 权限，无法继续"); return; }
        log("\n=== 清除依赖 ===");
        run(new Runnable() {
            @Override public void run() {
                exec("pm uninstall com.github.tvbox.osc.tk");
                log("TVBox: " + (hasPackage("com.github.tvbox.osc.tk") ? "仍在 ✗" : "已卸载 ✓"));
                ShResult r = exec("rm -rf '" + CH_DIR + "'");
                boolean removed = deleteDir(new File(CH_DIR));
                log(removed || r.code == 0 ? "频道源目录已删除" : "频道源目录不存在或删除失败");
                log("依赖清除完成");
            }
        });
    }

    // ================= 工具 =================

    private boolean copyFile(File src, File dst) {
        InputStream is = null;
        java.io.FileOutputStream fos = null;
        try {
            is = new java.io.FileInputStream(src);
            fos = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) { }
            try { if (fos != null) fos.close(); } catch (Throwable ignored) { }
        }
    }

    private boolean deleteDir(File d) {
        if (d == null || !d.exists()) return false;
        File[] fs = d.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        return d.delete();
    }

    private void run(final Runnable work) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { work.run(); } catch (Throwable t) { log("异常: " + t); }
            }
        }).start();
    }

    static class ShResult {
        int code;
        String err = "";
        String out = "";
    }

    /** 多行脚本：先落盘再用 sh 执行（SuperSU 对多行 -c 支持不好） */
    private ShResult execScript(String body, String name) {
        try {
            File f = new File(getCacheDir(), name + ".sh");
            OutputStream os = new FileOutputStream(f);
            os.write(body.replace("\r\n", "\n").getBytes("UTF-8"));
            os.close();
            return exec("sh " + f.getAbsolutePath());
        } catch (Throwable t) {
            ShResult r = new ShResult();
            r.code = -1;
            r.err = String.valueOf(t);
            return r;
        }
    }

    /** 以 root 执行一段 shell，输出写进日志 */
    private ShResult exec(String script) {
        ShResult r = new ShResult();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", script});
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String line;
            StringBuilder ob = new StringBuilder();
            while ((line = out.readLine()) != null) {
                ob.append(line).append("\\n");
                if (line.trim().length() > 0) log("> " + line);
            }
            r.out = ob.toString();
            StringBuilder eb = new StringBuilder();
            while ((line = err.readLine()) != null) {
                eb.append(line).append(" ");
            }
            r.err = eb.toString();
            r.code = p.waitFor();
        } catch (Throwable t) {
            r.code = -1;
            r.err = String.valueOf(t);
        }
        return r;
    }

    /** 检查 root 是否可用（会触发 SuperSU 授权框） */
    private boolean checkRoot() {
        ShResult r = exec("id");
        mRootOk = r.out.contains("uid=0");
        return mRootOk;
    }

    private boolean hasPackage(String pkg) {
        ShResult r = new ShResult();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "pm path " + pkg});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null && line.startsWith("package:");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把 assets 里的文件解到缓存目录 */
    private File unpack(String asset, String name) {
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            File out = new File(getCacheDir(), name);
            out.getParentFile().mkdirs();
            is = getAssets().open(asset);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            return out;
        } catch (Throwable t) {
            android.util.Log.e("TVDeskRestore", "unpack " + asset + " fail " + t);
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) { }
            try { if (fos != null) fos.close(); } catch (Throwable ignored) { }
        }
    }

    private void log(final String s) {
        android.util.Log.i("TVDeskRestore", s);
        ui.post(new Runnable() {
            @Override public void run() {
                if (mLog != null) mLog.append(s + "\n");
            }
        });
    }

    private GradientDrawable bg(int color, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? lighten(color) : color);
        d.setCornerRadius(dp(10));
        if (focused) d.setStroke(dp(4), 0xFFFFFFFF);
        return d;
    }

    private int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 55);
        int g = Math.min(255, ((c >> 8) & 0xFF) + 55);
        int b = Math.min(255, (c & 0xFF) + 55);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
