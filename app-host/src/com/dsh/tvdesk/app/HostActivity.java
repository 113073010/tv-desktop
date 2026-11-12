package com.dsh.tvdesk.app;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.stv.launcher.sdk.fragment.BaseFragment;

/**
 * 独立 APK 的宿主 Activity：直接承载原本给乐视桌面写的 Fragment。
 *
 * 在桌面里，Fragment 的 attach、显示/隐藏、销毁由 launcher 的插件容器驱动；
 * 独立应用里没有那套容器，就在这里补上：
 *   onCreate  → onAttach + onInflaterContent + 把它挂到窗口
 *   onResume  → onFragmentShowChanged(true)   （股票在这里开始拉行情）
 *   onPause   → onFragmentShowChanged(false)  （新闻在这里停止朗读）
 *   onDestroy → onDestroyView + onDetach
 */
public abstract class HostActivity extends Activity {

    private BaseFragment mPage;

    /** 子类返回要展示的页面。 */
    protected abstract BaseFragment createFragment();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(backdrop());

        FrameLayout root = new FrameLayout(this);
        root.setBackground(backdrop());
        setContentView(root);

        mPage = createFragment();
        mPage.onAttach(this);
        View content = mPage.onInflaterContent(getLayoutInflater(), root, savedInstanceState);
        if (content != null) {
            // 插件页面顶部留了 96/100dp 给桌面 Tab 条；独立应用没有 Tab 条，压成 22dp
            int dp = Math.round(getResources().getDisplayMetrics().density);
            content.setPadding(content.getPaddingLeft(), 22 * dp,
                    content.getPaddingRight(), content.getPaddingBottom());
            root.addView(content);

            // 桌面切到插件 Tab 时会调 onFocusRequested；独立应用自己补一次
            final BaseFragment page = mPage;
            root.post(new Runnable() {
                @Override public void run() { page.onFocusRequested(0); }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFullscreen();
        if (mPage != null) mPage.onFragmentShowChanged(true);
    }

    @Override
    protected void onPause() {
        if (mPage != null) mPage.onFragmentShowChanged(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mPage != null) {
            mPage.onDestroyView();
            mPage.onDetach();
            mPage = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    private void applyFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** 和插件页面背景的 0xE610141D 面板配套的深色底。 */
    private GradientDrawable backdrop() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A2433, 0xFF06090E});
    }
}
