package com.stv.launcher.sdk.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.stv.launcher.sdk.plugin.ActivityActionHandler;

/**
 * 桌面 BaseFragment 的独立运行版垫片。
 *
 * 方法签名与 launcher 里的真实类保持一致，页面代码不用改；
 * 生命周期由 {@link com.dsh.tvdesk.app.HostActivity} 手动驱动。
 */
public abstract class BaseFragment extends android.support.v4.app.Fragment implements ActivityActionHandler {

    public View mContainer;
    public String pluginPkgName;
    public String tag;

    public abstract boolean onFocusRequested(int direction);

    public abstract void onFragmentSeletedPre(boolean selected);

    public abstract void onFragmentShowChanged(boolean shown);

    public abstract boolean onHomeKeyEventHandled();

    public abstract View onInflaterContent(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState);

    public abstract void setHoverListener(View.OnHoverListener listener);

    @Override
    public Object onActivityAction(int actionId, Object obj) { return null; }

    public void setContentDescendantFocusability(int focusability) { }

    public View getContainer() { return mContainer; }
}
