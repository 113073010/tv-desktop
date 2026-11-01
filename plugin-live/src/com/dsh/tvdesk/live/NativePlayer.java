package com.dsh.tvdesk.live;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** 全屏播放：全屏 Dialog + TextureView，同样用 IjkProxy 的内核。 */
public class NativePlayer {

    public static void show(final Context ctx, String title, final String url, final Runnable onDismiss) {
        IjkProxy.init(ctx);

        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = dialog.getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.setDimAmount(0f);
        }

        final FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);

        final TextureView tv = new TextureView(ctx);
        tv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(tv);

        final IjkProxy.Player[] holder = new IjkProxy.Player[1];

        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                if (!IjkProxy.isReady()) return;
                final Surface surface = new Surface(st);
                try {
                    IjkProxy.Player p = new IjkProxy.Player();
                    holder[0] = p;
                    p.setSurface(surface);
                    p.setCallback(new IjkProxy.Callback() {
                        @Override public void onReady(int w, int h) {
                            try {
                                if (holder[0] != null) holder[0].start();
                            } catch (Throwable ignored) { }
                        }
                        @Override public void onError(int what, int extra) {
                            Diag.err("fs", "error " + what + "/" + extra);
                        }
                    });
                    p.applyTvOptions();
                    p.setDataSource(url);
                    p.prepareAsync();
                    Diag.log("fs", "opening " + url);
                } catch (Throwable t) {
                    Diag.err("fs", "fail " + t);
                }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w2, int h2) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        dialog.setOnKeyListener(new Dialog.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
                if (keyCode == KeyEvent.KEYCODE_BACK) dialog.dismiss();
                return true;
            }
        });

        dialog.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                if (holder[0] != null) holder[0].release();
                holder[0] = null;
                if (onDismiss != null) onDismiss.run();
            }
        });

        dialog.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.show();
    }
}
